package com.torrenthub.aio.util

import android.content.Context
import com.torrenthub.aio.data.SettingsRepository
import com.torrenthub.aio.data.SubtitleRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StorageUsageInfo(
    val webViewCacheBytes: Long = 0L,
    val subtitleTempBytes: Long = 0L,
    val historyAndLogsBytes: Long = 0L
) {
    val totalBytes: Long
        get() = webViewCacheBytes + subtitleTempBytes + historyAndLogsBytes
}

object StorageManagerHelper {

    private val SUBTITLE_TEMP_EXTS = setOf("srt", "ass", "ssa", "vtt", "sub", "tmp")

    private val WEBVIEW_CACHE_REL_PATHS = listOf(
        "Default/Cache",
        "Default/Code Cache",
        "Default/GPUCache",
        "Default/DawnCache",
        "Default/blob_storage",
        "Cache",
        "Code Cache",
        "GPUCache",
        "DawnCache",
        "HTTP Cache"
    )

    private val WEBVIEW_HISTORY_REL_PATHS = listOf(
        "Default/History",
        "Default/History-journal",
        "Default/Visited Links",
        "Default/Web Data",
        "Default/Web Data-journal",
        "Default/Session Storage",
        "Default/Top Sites",
        "Default/Top Sites-journal",
        "Default/Network Action Predictor",
        "Default/Network Action Predictor-journal",
        "crashpad",
        "BrowserMetrics",
        "BrowserMetrics-spare.pma"
    )

    fun formatStorageSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return Formatters.formatBytes(bytes)
    }

    fun buildDefaultBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "torrenthub_settings_$stamp.json"
    }

    private fun computePathBytes(target: File?, maxDepth: Int = 8): Long {
        if (target == null || !target.exists() || maxDepth < 0) return 0L
        return try {
            if (target.isFile) {
                target.length().coerceAtLeast(0L)
            } else {
                var sum = 0L
                val children = target.listFiles() ?: return 0L
                for (child in children) {
                    sum += computePathBytes(child, maxDepth - 1)
                }
                sum
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun isLooseSubtitleTempFile(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SUBTITLE_TEMP_EXTS && (
            ext != "tmp" || file.name.contains("sub", ignoreCase = true)
            )
    }

    private fun isLogDirOrFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.ROOT)
        return n == "logs" || n == "log" || n.endsWith(".log")
    }

    fun calculateStorageUsage(
        context: Context,
        settings: SettingsRepository,
        inMemoryHistoryAndLogsBytes: Long
    ): StorageUsageInfo {
        val appCtx = context.applicationContext
        val dataRoot = appCtx.dataDir
        val appWebViewDir = File(dataRoot, "app_webview")
        val subTempDir = SubtitleRepository.getTempSubtitlesDir(appCtx)

        var webViewBytes = 0L
        var subTempBytes = computePathBytes(subTempDir) +
            SubtitleRepository.estimatedUnbackedMemoryBytes() +
            settings.estimatedSavedJobProgressBytes()
        var historyLogsBytes = settings.estimatedPersistedHistoryBytes() +
            inMemoryHistoryAndLogsBytes.coerceAtLeast(0L)

        // 1) Measure app_webview cache vs history/logs
        if (appWebViewDir.exists()) {
            for (rel in WEBVIEW_CACHE_REL_PATHS) {
                webViewBytes += computePathBytes(File(appWebViewDir, rel))
            }
            for (rel in WEBVIEW_HISTORY_REL_PATHS) {
                historyLogsBytes += computePathBytes(File(appWebViewDir, rel))
            }
        }

        // 2) Measure cacheDir & externalCacheDir without double-counting subtitle_temp or logs
        val cacheRoots = listOfNotNull(appCtx.cacheDir, appCtx.externalCacheDir)
        for (root in cacheRoots) {
            val children = runCatching { root.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                when {
                    child.absolutePath == subTempDir.absolutePath -> Unit
                    isLooseSubtitleTempFile(child) -> subTempBytes += computePathBytes(child)
                    isLogDirOrFile(child) -> historyLogsBytes += computePathBytes(child)
                    else -> webViewBytes += computePathBytes(child)
                }
            }
        }

        // 3) Measure filesDir/logs if present
        historyLogsBytes += computePathBytes(File(appCtx.filesDir, "logs"))

        return StorageUsageInfo(
            webViewCacheBytes = webViewBytes.coerceAtLeast(0L),
            subtitleTempBytes = subTempBytes.coerceAtLeast(0L),
            historyAndLogsBytes = historyLogsBytes.coerceAtLeast(0L)
        )
    }

    private fun deleteChildrenSafely(dir: File?) {
        if (dir == null || !dir.exists()) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            runCatching { child.deleteRecursively() }
        }
    }

    fun clearWebViewAndTempSubtitleFilesOnDisk(context: Context) {
        val appCtx = context.applicationContext
        val subTempDir = SubtitleRepository.getTempSubtitlesDir(appCtx)

        // Clear SubtitleRepository memory + subtitle_temp disk directory
        SubtitleRepository.clearCache(appCtx)

        // Clear app_webview cache folders (preserving cookies/localStorage)
        val appWebViewDir = File(appCtx.dataDir, "app_webview")
        if (appWebViewDir.exists()) {
            for (rel in WEBVIEW_CACHE_REL_PATHS) {
                val target = File(appWebViewDir, rel)
                if (target.exists()) {
                    runCatching { target.deleteRecursively() }
                }
            }
        }

        // Clear cacheDir & externalCacheDir (preserving logs bucket so buckets remain independent)
        val cacheRoots = listOfNotNull(appCtx.cacheDir, appCtx.externalCacheDir)
        for (root in cacheRoots) {
            val children = runCatching { root.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.absolutePath == subTempDir.absolutePath || isLogDirOrFile(child)) continue
                runCatching { child.deleteRecursively() }
            }
        }
    }

    fun clearHistoryAndLogFilesOnDisk(context: Context) {
        val appCtx = context.applicationContext
        val appWebViewDir = File(appCtx.dataDir, "app_webview")
        if (appWebViewDir.exists()) {
            for (rel in WEBVIEW_HISTORY_REL_PATHS) {
                val target = File(appWebViewDir, rel)
                if (target.exists()) {
                    runCatching { target.deleteRecursively() }
                }
            }
        }
        deleteChildrenSafely(File(appCtx.filesDir, "logs"))
        deleteChildrenSafely(File(appCtx.cacheDir, "logs"))
        appCtx.externalCacheDir?.let { deleteChildrenSafely(File(it, "logs")) }
    }
}
